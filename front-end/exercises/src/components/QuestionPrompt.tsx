import React from 'react';
import { Question } from '../types';
import Lottie from 'lottie-react';
import lottieFile1 from './tiger.json';
import lottieFile2 from './panda.json';
import lottieFile3 from './giraffe.json';
import lottieFile4 from './strawberry.json';
import lottieFile5 from './cat.json';

interface QuestionPromptProps {
    question: Question;
}
const QuestionPrompt: React.FC<QuestionPromptProps> = ({ question }) => {
    return (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '50vh', flexDirection: 'row'}}>
            <div style={{ flex: 1, display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
                <Lottie 
                animationData={
                question.type === 'fill_in_blank' ? lottieFile1 : 
                question.type === 'numeric' ? lottieFile2 : 
                question.type === 'true_false' ? lottieFile3 : 
                question.type === 'multiple_choice' ? lottieFile4 : 
                question.type === 'checkbox' ? lottieFile5 :
                null
                } 
                loop={true} 
                style={{height: '100%', width: '100%', maxHeight: '500px', maxWidth: '500px'}} 
                />
            </div>
            <div style={{ flex: 2, display: 'flex', justifyContent: 'center', alignItems: 'center', fontWeight: 'bold', fontSize: '1em' }}>
                <div dangerouslySetInnerHTML={{ __html: question.prompt }}></div>
            </div>
        </div>
    )
};

export default QuestionPrompt;