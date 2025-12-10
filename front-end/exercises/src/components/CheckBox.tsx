import React from 'react';
import { useRef } from "react";

interface CheckBoxProps {
    choices: string[]
    scrambled: boolean
    handleSubmit: (event: React.FormEvent<HTMLFormElement>) => void;
    setStudentAnswer: (studentAnswer: string) => void;
}

// Utility function to shuffle an array using the Fisher-Yates algorithm
const shuffleArray = (array: string[]) => {
    for (let i = array.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [array[i], array[j]] = [array[j], array[i]];
    }
    return array;
};

const CheckBox: React.FC<CheckBoxProps> = ({ choices, scrambled, handleSubmit, setStudentAnswer }) => {
    const [, setStudentAnswerState] = React.useState<string[]>([]);
    const valuesRef = useRef<string[]>("abcdefg".split('').slice(0, choices.length));
    const shuffleRef = useRef<boolean>(scrambled);
    const hasLongChoice = choices.some(choice => choice.length > 40);
    
    if (shuffleRef.current) {
        valuesRef.current = shuffleArray([...valuesRef.current]);
        shuffleRef.current = false; // Ensures that choices are only shuffled once
    }
            
    const assembleStudentAnswer = (choice: string) => {
        setStudentAnswerState(prevState => {
            let updatedAnswer;
            if (prevState.includes(choice)) {
                updatedAnswer = prevState.filter(item => item !== choice);
            } else {
                updatedAnswer = [...prevState, choice].sort();
            }
            setStudentAnswer(updatedAnswer.join(''));
            return updatedAnswer;
        });
    }
    
    return (
        <form onSubmit={handleSubmit} style={{ display: 'flex', alignItems: 'center' }}>
            <div className="form-check" style={{ flex: 1, borderStyle: 'solid', padding: '15px', paddingLeft: '50px' }}>
                {valuesRef.current.map((value, index) => (
                    <label key={index} style={{ display: 'block', whiteSpace: hasLongChoice ? 'normal' : 'nowrap' }}>
                        <input className="form-check-input" type="checkbox" name="checkbox" value={value} onChange={(e) => { assembleStudentAnswer(e.target.value); }} />
                        <span dangerouslySetInnerHTML={{ __html: choices[value.charCodeAt(0)-97] }} />
                    </label>
                ))}
            </div>
            <div style={{ flex: 1, paddingLeft: '15px', textAlign: 'center' }}>
                <button className="btn btn-primary" type="submit">Submit</button>
            </div>
        </form>
    );
}

export default CheckBox;